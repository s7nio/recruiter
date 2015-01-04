package de.th.wildau.recruiter.web;

import javax.annotation.PostConstruct;
import javax.faces.view.ViewScoped;
import javax.inject.Inject;
import javax.inject.Named;

import lombok.Getter;
import lombok.Setter;
import de.th.wildau.recruiter.ejb.BusinessError;
import de.th.wildau.recruiter.ejb.BusinessException;
import de.th.wildau.recruiter.ejb.model.Article;
import de.th.wildau.recruiter.ejb.service.ArticleService;

/**
 * Controller bean to edit a article from me (folder /my/edit.xhtml).
 * 
 * @author s7n
 *
 */
@Named
@ViewScoped
public class EditArticleHome extends AbstractHome {

	private static final long serialVersionUID = 6444486672653043318L;

	@Getter
	@Setter
	private Article article;

	@Inject
	private ArticleService articleService;

	@Inject
	private DashboardHome dashboard;

	/**
	 * Delete current article.
	 * 
	 * @return String navigation
	 */
	public String deleteArticle() {
		return this.dashboard.deleteArticle(this.article.getId());
	}

	@PostConstruct
	public void init() {
		try {
			this.article = this.articleService.findArticle(Long
					.valueOf(getParam("id")));
		} catch (final NumberFormatException e) {
			addErrorMessage(new BusinessException(BusinessError.INVALID_VIEW_ID));
		}
	}

	public boolean isMy() {
		return this.article != null;
	}

	/**
	 * Update the current article.
	 * 
	 * @return String navigation
	 */
	public String updateArticle() {
		try {
			this.articleService.update(this.article);
			addInfoMessage("msg.article.updated");
			return redirect("/my");
		} catch (final BusinessException e) {
			addErrorMessage(e);
		}
		return "";
	}
}